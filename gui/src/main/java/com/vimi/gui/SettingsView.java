package com.vimi.gui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Application settings navigation with durable product visibility controls, and the attribution of
 * third-party data bundled with the application.
 */
final class SettingsView extends BorderPane {
    private final SettingsService service;
    private final Runnable changed;
    private final VBox products = new VBox(8);
    private final TextField search = new TextField();
    private final Label status = label("", "muted");
    private final VBox controls = new VBox(16);
    private boolean busy;
    private final ActivityIndicator saving = new ActivityIndicator("Saving product preferences…");
    private final Button reset = new Button("Restore defaults");

    SettingsView(SettingsService service, Runnable changed) {
        this.service = Objects.requireNonNull(service);
        this.changed = Objects.requireNonNull(changed);
        ListView<String> sections = new ListView<>();
        sections.getItems().addAll("Products", "Bundled data");
        sections.getSelectionModel().selectFirst();
        sections.setId("settings-sections");
        sections.setAccessibleText("Settings areas");
        sections.getStyleClass().add("product-menu");
        VBox sidebar = new VBox(16, label("SETTINGS", "eyebrow"), sections);
        sidebar.getStyleClass().add("product-sidebar");
        sidebar.setPadding(new Insets(20, 10, 20, 10));
        sidebar.setPrefWidth(230);
        sidebar.setMinWidth(210);
        VBox.setVgrow(sections, Priority.ALWAYS);
        setLeft(sidebar);

        reset.setOnAction(event -> command(service.resetProducts()));
        HBox heading = new HBox(16, label("Products", "h1"), space(), reset);
        heading.setAlignment(Pos.CENTER_LEFT);
        search.setPromptText("Find a product by name or ID");
        search.setAccessibleText("Filter product settings");
        search.textProperty().addListener((observable, before, after) -> render());
        TextField extra = new TextField();
        extra.setPromptText("Additional WoW product ID");
        extra.setAccessibleText("Additional WoW product ID");
        Button include = new Button("Include product");
        include.setOnAction(
                event -> {
                    String id = extra.getText().strip().toLowerCase(Locale.ROOT);
                    if (id.isEmpty()) return;
                    command(service.setEnabled(id, true));
                });
        extra.setOnAction(event -> include.fire());
        HBox additional = new HBox(10, extra, include);
        HBox.setHgrow(extra, Priority.ALWAYS);
        controls.getChildren().addAll(search, products, additional);
        VBox content =
                new VBox(
                        16,
                        heading,
                        label("Choose which WoW products appear in Builds.", "subtitle"),
                        label(
                                "Turning a product off hides its builds and skips its catalog"
                                        + " refresh. Stored data and existing downloads are kept.",
                                "muted"),
                        status,
                        saving,
                        controls);
        content.setPadding(new Insets(28));
        content.setMaxWidth(1000);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        ScrollPane[] bundled = {null};
        setCenter(scroll);
        sections.getSelectionModel()
                .selectedItemProperty()
                .addListener(
                        (observable, before, after) -> {
                            if (!"Bundled data".equals(after)) {
                                setCenter(scroll);
                                return;
                            }
                            // Built on first view; an unreadable notice shows one line instead.
                            if (bundled[0] == null) bundled[0] = scroll(bundledDataOrProblem());
                            setCenter(bundled[0]);
                        });
        render();
    }

    /** The attribution lines the Bundled data page shows for the bundled table definitions. */
    static java.util.List<String> bundledDefinitionsLines() {
        var attribution = com.vibeminer.formats.definitions.BundledDefinitions.attribution();
        return java.util.List.of(
                attribution.name() + " · Table definitions",
                "By " + attribution.authors(),
                "Revision " + attribution.revision() + " (pinned)",
                "License: " + attribution.license() + " · " + attribution.licenseUrl(),
                "Source: " + attribution.upstream(),
                "Ships with the application and is verified against its manifest and source"
                        + " hashes each time it loads. A newer revision comes with an application"
                        + " update; nothing is downloaded.");
    }

    private static VBox bundledDataOrProblem() {
        try {
            return bundledData();
        } catch (RuntimeException unreadable) {
            VBox content =
                    new VBox(
                            16,
                            label("Bundled data", "h1"),
                            label(
                                    "The bundled data notice could not be read. The notices are in"
                                            + " the application's legal folder (THIRD-PARTY.md).",
                                    "muted"));
            content.setPadding(new Insets(28));
            return content;
        }
    }

    /** Attribution for third-party data that ships with the application (not a data source). */
    static VBox bundledData() {
        var attribution = com.vibeminer.formats.definitions.BundledDefinitions.attribution();
        var lines = bundledDefinitionsLines();
        Label name = label(lines.getFirst(), "h2");
        name.setId("bundled-definitions-name");
        VBox facts = new VBox(6, name);
        for (int i = 1; i < lines.size(); i++) {
            facts.getChildren()
                    .add(label(lines.get(i), i == 1 || i == lines.size() - 1 ? "muted" : "value"));
        }
        facts.getStyleClass().add("source-panel");
        facts.setPadding(new Insets(14));
        Label notice = label(attribution.notice(), "muted");
        notice.setId("bundled-definitions-notice");
        TitledPane noticePane = new TitledPane("Notice", notice);
        noticePane.setExpanded(false);
        VBox content =
                new VBox(
                        16,
                        label("Bundled data", "h1"),
                        label(
                                "Third-party data that ships with the application, with its"
                                        + " license and notice.",
                                "subtitle"),
                        facts,
                        noticePane,
                        label(
                                "Notices for the bundled runtime libraries are in the"
                                        + " application's legal folder (THIRD-PARTY.md).",
                                "muted"));
        content.setId("settings-bundled-data");
        content.setPadding(new Insets(28));
        content.setMaxWidth(1000);
        return content;
    }

    private static ScrollPane scroll(VBox content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
    }

    private void render() {
        var snapshot = service.snapshot();
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        products.getChildren().clear();
        long count = snapshot.products().stream().filter(SettingsService.Product::enabled).count();
        if (!busy) status.setText(count + " products enabled · Changes are saved automatically");
        boolean shownDefaults = false;
        boolean shownOther = false;
        for (var product : snapshot.products()) {
            if (!(product.name() + " " + product.id()).toLowerCase(Locale.ROOT).contains(query))
                continue;
            if (product.defaultEnabled() && !shownDefaults) {
                products.getChildren().add(label("DEFAULT PRODUCTS", "eyebrow"));
                shownDefaults = true;
            } else if (!product.defaultEnabled() && !shownOther) {
                products.getChildren().add(label("OTHER PRODUCTS", "eyebrow"));
                shownOther = true;
            }
            CheckBox enabled = new CheckBox(product.name());
            enabled.setSelected(product.enabled());
            enabled.setAccessibleText("Include " + product.name() + " (" + product.id() + ")");
            enabled.setOnAction(
                    event -> command(service.setEnabled(product.id(), enabled.isSelected())));
            VBox identity = new VBox(4, enabled, label(product.id(), "muted"));
            identity.setPadding(new Insets(0, 0, 0, 2));
            HBox row = new HBox(12, identity, space());
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(11, 14, 11, 14));
            row.getStyleClass().add("source-panel");
            if (!product.reviewed()) row.getChildren().add(label("Discovered product", "muted"));
            products.getChildren().add(row);
        }
        if (products.getChildren().isEmpty())
            products.getChildren().add(label("No matching products", "muted"));
    }

    private void command(CompletableFuture<Void> result) {
        busy = true;
        saving.setBusy(true);
        controls.setDisable(true);
        reset.setDisable(true);
        status.setText("");
        result.whenComplete(
                (value, failure) ->
                        Platform.runLater(
                                () -> {
                                    busy = false;
                                    saving.setBusy(false);
                                    controls.setDisable(false);
                                    reset.setDisable(false);
                                    render();
                                    if (failure == null) changed.run();
                                    else {
                                        Throwable cause = failure;
                                        while (cause
                                                        instanceof
                                                        java.util.concurrent.CompletionException
                                                && cause.getCause() != null)
                                            cause = cause.getCause();
                                        status.setText(
                                                cause instanceof IllegalArgumentException
                                                        ? "Use a WoW product ID such as wow,"
                                                                + " wow_beta or wowf."
                                                        : "Product preferences could not be saved."
                                                                + " Please retry.");
                                    }
                                }));
    }

    private static Label label(String value, String style) {
        Label label = new Label(value);
        label.setWrapText(true);
        label.getStyleClass().add(style);
        return label;
    }

    private static Region space() {
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        return space;
    }
}
