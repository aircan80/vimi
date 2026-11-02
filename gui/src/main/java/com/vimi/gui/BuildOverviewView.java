package com.vimi.gui;

import java.util.Locale;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Landing-page presentation only. All research totals come from authored mock snapshots. */
public final class BuildOverviewView {
    private BuildOverviewView() {}

    public static Node create(BuildInfo build, String region, ObjectProperty<String> mode,
            Consumer<String> navigate) {
        MockOverviewSnapshot data = MockOverviewSnapshot.forBuild(build);
        VBox page = new VBox(18);
        page.setPadding(new Insets(24));
        HBox title = new HBox(16, text("Build overview", "h1"), space(), text("AUTHORED MOCK SNAPSHOT", "badge"));
        title.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        page.getChildren().addAll(title, text("Explore this exact build’s client data and choose what to retain locally.", "subtitle"));

        String names = data.namedFileIds() == null ? "Not measured" : String.format(Locale.ROOT,
                "%.1f%%", 100.0 * data.namedFileIds() / data.fileIds());
        HBox metrics = new HBox(14,
                metric("FILES CATALOGUED", count(data.fileIds()), "Distinct FileDataIDs · all catalog variants"),
                metric("READABLE TABLES", count(data.readableTables()), data.tableDefinitions() == null
                        ? "Definitions and records not assessed" : "of " + count(data.tableDefinitions()) + " bundled definitions"),
                metric("NAMED FILES", names, data.namedFileIds() == null ? "Filename coverage not assessed"
                        : count(data.namedFileIds()) + " community names · same catalog scope"),
                metric("STORED CONTENT", gib(build.storedGiB()), build.totalGiB() == null
                        ? "Total encoded scope unknown" : build.downloaded() + " of measured encoded scope"));
        for (Node child : metrics.getChildren()) {
            HBox.setHgrow(child, Priority.ALWAYS);
            ((Region) child).setPrefWidth(240);
            ((Region) child).setMaxWidth(Double.MAX_VALUE);
        }
        page.getChildren().add(metrics);

        VBox explore = new VBox(14, text("Explore this build", "h2"),
                text("Illustrative scope totals below; each browser contains a small, curated sample subset.", "muted"));
        GridPane destinations = new GridPane();
        destinations.setHgap(14);
        destinations.setVgap(14);
        destinations.add(destination("Files", count(data.fileIds()) + " distinct file IDs",
                "Explore catalog membership, attributed names and local availability.", navigate), 0, 0);
        destinations.add(destination("Tables", count(data.readableTables()) + " readable tables",
                "Inspect records, field definitions and declared relationships.", navigate), 1, 0);
        destinations.add(destination("Items", count(data.itemRecords()) + " indexed item records",
                "Research enUS names, classifications and client properties.", navigate), 0, 1);
        destinations.add(destination("Creatures", count(data.creatureDeclarations()) + " client declarations",
                "Inspect names and display declarations; no live spawn data.", navigate), 1, 1);
        for (Node child : destinations.getChildren()) {
            GridPane.setHgrow(child, Priority.ALWAYS);
            ((Region) child).setMaxWidth(Double.MAX_VALUE);
        }
        for (int index = 0; index < 2; index++) {
            javafx.scene.layout.ColumnConstraints column = new javafx.scene.layout.ColumnConstraints();
            column.setPercentWidth(50);
            destinations.getColumnConstraints().add(column);
        }
        explore.getChildren().add(destinations);
        explore.setMinWidth(0);
        HBox.setHgrow(explore, Priority.ALWAYS);

        VBox retention = retention(build, mode);
        retention.setPrefWidth(345);
        retention.setMinWidth(300);
        retention.setMaxWidth(370);
        HBox main = new HBox(20, explore, retention);
        page.getChildren().add(main);

        HBox readiness = new HBox(22,
                fact("Configuration", "Verified · mock"),
                fact("File catalog", data.fileIds() == null ? "Not assessed" : "Catalogued · mock"),
                fact("Table data", data.readableTables() == null ? "Not assessed" : "Partial · 608 of 812 tables"),
                fact("Remote availability", "Not checked"));
        readiness.getStyleClass().add("readiness-strip");
        page.getChildren().add(readiness);
        VBox evidence = new VBox(12,
                fact("Build source", build.source().label() + " · " + build.source().description()),
                fact("Build configuration", build.config()),
                fact("Acquisition route / research locale", region + " / enUS"),
                text("Catalog figures count FileDataIDs. Storage figures measure encoded content across published variants; these are different scopes. Community names are provider metadata. No comparison build is selected, so no change counts are inferred.", "muted"));
        evidence.setPadding(new Insets(14));
        TitledPane details = new TitledPane("Build identity & measurement scope", evidence);
        details.setExpanded(false);
        details.setAnimated(false);
        page.getChildren().addAll(details, text("Prototype only · no data is downloaded, decoded or persisted. All figures and readiness states are authored examples.", "muted"));
        return page;
    }

    private static VBox retention(BuildInfo build, ObjectProperty<String> mode) {
        Label state = text("", "value");
        state.textProperty().bind(Bindings.when(mode.isEqualTo("Download all"))
                .then("Download all selected · no transfer running")
                .otherwise("On demand · acquire content as you explore"));
        RadioButton demand = new RadioButton("On demand");
        RadioButton all = new RadioButton("Download all");
        ToggleGroup policy = new ToggleGroup();
        demand.setToggleGroup(policy);
        all.setToggleGroup(policy);
        if (build.totalGiB() == null) {
            mode.set("On demand");
            all.setDisable(true);
        }
        policy.selectToggle("Download all".equals(mode.get()) ? all : demand);
        policy.selectedToggleProperty().addListener((observable, previous, selected) -> {
            if (selected != null) {
                mode.set(selected == all ? "Download all" : "On demand");
            }
        });
        HBox choice = new HBox(16, demand, all);
        choice.getStyleClass().add("download-choice");
        VBox panel = new VBox(14, text("Download & storage", "h2"),
                text("Keep only what you explore, or retain this build’s measured encoded scope.", "muted"),
                choice, state);
        panel.getStyleClass().addAll("card", "retention-panel");
        if (build.totalGiB() == null) {
            panel.getChildren().addAll(text("Size unknown", "metric"),
                    fact("Stored encoded content", gib(build.storedGiB())),
                    text("No supported size measurement in this fixture. Download all needs a known scope; on-demand research remains available.", "muted"));
        } else {
            panel.getChildren().add(text(build.downloaded() + " retained", "storage-metric"));
            ProgressBar progress = new ProgressBar(build.storedGiB() / build.totalGiB());
            progress.setMaxWidth(Double.MAX_VALUE);
            progress.setAccessibleText("Mock encoded scope retained: " + build.downloaded());
            panel.getChildren().addAll(progress,
                    fact("Stored / published encoded content", gib(build.storedGiB()) + " / " + gib(build.totalGiB())),
                    fact("Additional encoded content", gib(build.totalGiB() - build.storedGiB())));
        }
        panel.getChildren().addAll(text("Research stays available in either mode. This mock setting is kept for the current session only.", "muted"),
                text("Shared content is counted once. These are not installed-game sizes or reclaimable disk estimates.", "muted"));
        return panel;
    }

    private static VBox destination(String area, String value, String explanation, Consumer<String> navigate) {
        if (value.startsWith("Not measured")) {
            value = "Scope not measured";
        }
        Button open = new Button("Explore " + area.toLowerCase(Locale.ROOT) + " →");
        open.setOnAction(event -> navigate.accept(area));
        open.getStyleClass().add("explore-action");
        VBox card = new VBox(10, text(area, "h2"), text(value, "value"), text(explanation, "muted"), open);
        card.getStyleClass().addAll("card", "destination-card");
        return card;
    }

    private static VBox metric(String title, String value, String scope) {
        VBox box = new VBox(8, text(title, "eyebrow"), text(value, "landing-metric"), text(scope, "muted"));
        box.getStyleClass().addAll("card", "landing-stat");
        return box;
    }

    private static VBox fact(String name, String value) {
        return new VBox(4, text(name, "muted"), text(value, "value"));
    }

    private static Label text(String value, String style) {
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

    private static String count(Long value) {
        return value == null ? "Not measured" : String.format(Locale.US, "%,d", value);
    }

    private static String gib(double value) {
        return String.format(Locale.ROOT, "%.1f GiB", value);
    }
}
