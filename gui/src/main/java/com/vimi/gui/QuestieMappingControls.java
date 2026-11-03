package com.vimi.gui;

import javafx.application.Platform;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** A per-build flavor override; does not acquire provider data or change the source default. */
final class QuestieMappingControls extends VBox {
    private final DataSourcesService sources;
    private final BuildInfo build;
    private final ComboBox<String> flavor = new ComboBox<>();
    private final Label resolved = new Label(), database = new Label(), problem = new Label();
    private boolean updating;
    private final ActivityIndicator saving = new ActivityIndicator("Saving Questie mapping…");

    QuestieMappingControls(DataSourcesService sources, BuildInfo build) {
        super(10);
        this.sources = sources;
        this.build = build;
        flavor.getItems().addAll("Automatic", "None");
        for (var value : com.vibeminer.questie.QuestieTypes.Flavor.values())
            flavor.getItems().add(value.upstream());
        flavor.setAccessibleText("Questie flavor mapping for this build");
        flavor.getStyleClass().add("build-choice");
        flavor.setOnAction(
                event -> {
                    if (updating || flavor.getValue() == null) return;
                    flavor.setDisable(true);
                    saving.setBusy(true);
                    sources.setMapping(build, flavor.getValue())
                            .whenComplete(
                                    (value, error) ->
                                            Platform.runLater(
                                                    () -> {
                                                        flavor.setDisable(false);
                                                        saving.setBusy(false);
                                                        problem.setText(
                                                                error == null
                                                                        ? ""
                                                                        : "Mapping could not be"
                                                                              + " saved");
                                                        refresh();
                                                    }));
                });
        for (var label : new Label[] {resolved, database, problem}) {
            label.setWrapText(true);
            label.getStyleClass().add("muted");
        }
        getChildren().addAll(new Label("Questie flavor"), flavor, saving, resolved, database, problem);
        refresh();
    }

    void refresh() {
        var mapping = sources.mapping(build);
        updating = true;
        try {
            flavor.setValue(mapping.choice());
        } finally {
            updating = false;
        }
        resolved.setText(
                mapping.flavor() == null ? "No flavor selected" : "Flavor: " + mapping.flavor());
        database.setText(mapping.message());
    }
}
